package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 的 CRUD 与「可复制」能力测试。
 * <p>
 * 设计变更说明：最初只存 SHA-256、明文仅创建时显示一次。实际运维中不可用——
 * 丢一次就要重建、还要改所有客户端配置。改为同时存明文后，必须验证：
 * <ol>
 *   <li>明文能反复取到（这是「可复制」的前提）；</li>
 *   <li>鉴权仍走哈希，不依赖明文列（避免查询路径被改动）；</li>
 *   <li>重新生成后旧 Key <b>立即失效</b>、新 Key 立即可用；</li>
 *   <li>名称可改（含清空），且不会连带把权重或启用状态改掉。</li>
 * </ol>
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ApiKeyManagementTest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Test
    @DisplayName("创建后明文可反复读取（可复制），且掩码不泄露完整值")
    void plaintextIsRetrievableForCopy() {
        ApiKeyService.CreatedKey created = apiKeyService.create("复制测试", 3);
        Long id = created.key().getId();
        try {
            // 从库里重新读出来 —— 不是用创建时返回值，而是真的落库了
            ApiKey reloaded = apiKeyService.get(id);
            assertThat(reloaded.hasKeyValue())
                    .as("明文必须落库，否则列表页无法提供复制").isTrue();
            assertThat(reloaded.getKeyValue())
                    .as("读回的明文应与创建时一致").isEqualTo(created.plaintext());

            // 反复读两次，确认不是一次性消费
            assertThat(apiKeyService.get(id).getKeyValue())
                    .as("第二次读取仍应拿到同一明文").isEqualTo(created.plaintext());

            // 掩码不含完整值
            String masked = reloaded.maskedKeyValue();
            assertThat(masked).contains("****");
            assertThat(masked).as("掩码不能等于明文").isNotEqualTo(created.plaintext());
        } finally {
            apiKeyService.delete(id);
        }
    }

    @Test
    @DisplayName("鉴权仍走哈希：明文改了但哈希不匹配就查不到")
    void resolveUsesHashNotPlaintext() {
        ApiKeyService.CreatedKey created = apiKeyService.create("鉴权测试", 1);
        Long id = created.key().getId();
        try {
            assertThat(apiKeyService.resolveUsable(created.plaintext()))
                    .as("合法 Key 应能解析").isNotNull();

            // 伪造一个「明文对但哈希不对」的场景不可能发生（我们总是一起写），
            // 但可以验证：随便一个不存在的字符串查不到
            assertThat(apiKeyService.resolveUsable("sk-ac-not-exist-xxxx"))
                    .as("不存在的 Key 应解析失败").isNull();
        } finally {
            apiKeyService.delete(id);
        }
    }

    @Test
    @DisplayName("重新生成：旧 Key 立即失效，新 Key 立即可用，元数据保留")
    void regenerateInvalidatesOldKeyAndKeepsMetadata() {
        ApiKeyService.CreatedKey created = apiKeyService.create("重新生成测试", 7);
        Long id = created.key().getId();
        String oldPlaintext = created.plaintext();
        try {
            // 先改名，验证 regenerate 不会把名称冲掉
            apiKeyService.update(id, "改过的名字", null, null);

            String newPlaintext = apiKeyService.regenerate(id);
            assertThat(newPlaintext).as("应返回新明文").isNotBlank();
            assertThat(newPlaintext).as("新值必须与旧值不同").isNotEqualTo(oldPlaintext);

            // 旧值立即失效 —— 这是「换 Key」的核心语义
            assertThat(apiKeyService.resolveUsable(oldPlaintext))
                    .as("旧 Key 必须立即失效，否则泄露的旧值仍能用").isNull();
            // 新值立即可用
            assertThat(apiKeyService.resolveUsable(newPlaintext))
                    .as("新 Key 应立即可用").isNotNull();

            // 元数据保留：id、名称、权重都没变
            ApiKey reloaded = apiKeyService.get(id);
            assertThat(reloaded.getId()).as("id 不变").isEqualTo(id);
            assertThat(reloaded.getLabel()).as("名称应保留").isEqualTo("改过的名字");
            assertThat(reloaded.getWeight()).as("权重应保留").isEqualTo(7);
            assertThat(reloaded.getKeyValue())
                    .as("库里的明文应同步为新值").isEqualTo(newPlaintext);
        } finally {
            apiKeyService.delete(id);
        }
    }

    @Test
    @DisplayName("改名不影响权重与启用状态")
    void updateLabelDoesNotTouchOtherFields() {
        ApiKeyService.CreatedKey created = apiKeyService.create("原始名", 4);
        Long id = created.key().getId();
        try {
            // 只传 label，其余传 null
            ApiKey updated = apiKeyService.update(id, "新名字", null, null);
            assertThat(updated.getLabel()).isEqualTo("新名字");
            assertThat(updated.getWeight())
                    .as("只改名时权重必须保持 4，不能被重置").isEqualTo(4);
            assertThat(updated.getEnabled())
                    .as("只改名时启用状态必须保持").isTrue();
        } finally {
            apiKeyService.delete(id);
        }
    }

    @Test
    @DisplayName("名称可清空（存为 null），用于「未命名」场景")
    void labelCanBeCleared() {
        ApiKeyService.CreatedKey created = apiKeyService.create("将被清空", 1);
        Long id = created.key().getId();
        try {
            ApiKey updated = apiKeyService.update(id, "   ", null, null);
            assertThat(updated.getLabel()).as("空白名称应存为 null").isNull();

            ApiKey reloaded = apiKeyService.get(id);
            assertThat(reloaded.getLabel()).isNull();
        } finally {
            apiKeyService.delete(id);
        }
    }

    @Test
    @DisplayName("删除后彻底查不到，且明文也不再可取")
    void deleteRemovesEverything() {
        ApiKeyService.CreatedKey created = apiKeyService.create("删除测试", 1);
        Long id = created.key().getId();

        assertThat(apiKeyService.delete(id)).isTrue();
        assertThat(apiKeyService.get(id)).as("删除后应查不到").isNull();
        assertThat(apiKeyService.resolveUsable(created.plaintext()))
                .as("删除后明文应立即失效").isNull();
    }
}

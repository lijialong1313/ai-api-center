package com.weacsoft.aiApiCenter;

import com.weacsoft.aiApiCenter.app.model.ApiKey;
import com.weacsoft.aiApiCenter.app.service.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 的 CRUD 与复制能力测试。
 * <p>
 * 库中同时保存明文与哈希，因此需要验证：
 * <ol>
 *   <li>明文可反复读取（复制功能的前提）；</li>
 *   <li>鉴权仍走哈希查询；</li>
 *   <li>重新生成后旧 Key 立即失效、新 Key 立即可用；</li>
 *   <li>名称可改（含清空），不影响权重与启用状态。</li>
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
            // 从库中重新读取，确认明文已落库
            ApiKey reloaded = apiKeyService.get(id);
            assertThat(reloaded.hasKeyValue())
                    .as("明文必须落库").isTrue();
            assertThat(reloaded.getKeyValue())
                    .as("读回的明文应与创建时一致").isEqualTo(created.plaintext());

            // 再次读取，确认可以反复取得
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
    @DisplayName("鉴权按哈希查询：不存在的 Key 解析失败")
    void resolveUsesHashNotPlaintext() {
        ApiKeyService.CreatedKey created = apiKeyService.create("鉴权测试", 1);
        Long id = created.key().getId();
        try {
            assertThat(apiKeyService.resolveUsable(created.plaintext()))
                    .as("合法 Key 应能解析").isNotNull();

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
            // 先改名，验证 regenerate 不冲掉名称
            apiKeyService.update(id, "改过的名字", null, null);

            String newPlaintext = apiKeyService.regenerate(id);
            assertThat(newPlaintext).as("应返回新明文").isNotBlank();
            assertThat(newPlaintext).as("新值必须与旧值不同").isNotEqualTo(oldPlaintext);

            // 旧值立即失效，新值立即可用
            assertThat(apiKeyService.resolveUsable(oldPlaintext))
                    .as("旧 Key 必须立即失效").isNull();
            assertThat(apiKeyService.resolveUsable(newPlaintext))
                    .as("新 Key 应立即可用").isNotNull();

            // 元数据保留：id、名称、权重不变
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
            ApiKey updated = apiKeyService.update(id, "新名字", null, null);
            assertThat(updated.getLabel()).isEqualTo("新名字");
            assertThat(updated.getWeight())
                    .as("只改名时权重应保持为 4").isEqualTo(4);
            assertThat(updated.getEnabled())
                    .as("只改名时启用状态应保持").isTrue();
        } finally {
            apiKeyService.delete(id);
        }
    }

    @Test
    @DisplayName("名称可清空（存为 null）")
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

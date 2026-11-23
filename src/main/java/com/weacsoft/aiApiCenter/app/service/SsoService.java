package com.weacsoft.aiApiCenter.app.service;

import com.weacsoft.aiApiCenter.config.SsoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 统一身份认证客户端（广州城市理工学院智慧校园认证系统，文档 v1.0.4）。
 * <p>
 * 本系统使用 OAuth2 授权码模式（文档第 1 章），流程：
 * <ol>
 *   <li>浏览器跳到认证系统的 {@code /application/oauth/redirect}（带 client_id / callbackUrl / redirect_uri）；</li>
 *   <li>用户在认证系统页面完成登录（验证码等由认证方处理，本系统不接触用户口令）；</li>
 *   <li>认证系统带 {@code code} 回调本系统的 {@code /admin/sso/callback}；</li>
 *   <li>服务端用 {@code code} 换 {@code access_token}；</li>
 *   <li>服务端用 {@code access_token} 取用户信息（学工号 / 姓名 / 部门 / 角色）。</li>
 * </ol>
 * <p>
 * 文档第 2 章的「接口登录」（{@link #apiLogin}，DES-CBC 加密口令）本系统不启用，
 * 管理后台登录只走授权码模式。该实现与 {@link #desEncrypt} 保留并带有文档测试向量校验。
 */
@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    /** 会话中保存本次登录发起时使用的 redirect_uri，保证「授权」与「换令牌」两端一致 */
    public static final String SESSION_REDIRECT_URI = "sso.redirect_uri";

    private final SsoProperties props;

    public SsoService(SsoProperties props) {
        this.props = props;
    }

    /**
     * SSO 返回的用户身份。
     *
     * @param staffNo  学工号（文档的 number / uid）
     * @param name     姓名
     * @param college  部门
     * @param roleCode 文档的 role：教师是 0，学生是 1
     * @param roleName 文档的 roleName
     * @param allRoles 全部角色名列表
     */
    public record SsoUser(String staffNo, String name, String college,
                          String roleCode, String roleName, List<String> allRoles) {

        /** 是否为教师（role=0 或 allRole 中含「教师」） */
        public boolean isTeacher() {
            if ("0".equals(roleCode)) {
                return true;
            }
            return allRoles != null && allRoles.stream()
                    .anyMatch(r -> r != null && r.contains("教师"));
        }
    }

    /**
     * 步骤 1：构造跳转到统一身份认证的地址。
     *
     * @param redirectUri 认证系统回调本系统的绝对地址（需 urlencode 后放入）
     * @return 完整授权地址
     */
    public String buildAuthorizeUrl(String redirectUri) {
        String cb = urlEncode(props.getCallbackUrl());
        String ru = urlEncode(redirectUri);
        return props.getBaseUrl()
                + "/application/oauth/redirect"
                + "?client_id=" + urlEncode(props.getClientId())
                + "&callbackUrl=" + cb
                + "&redirect_uri=" + ru;
    }

    /**
     * 步骤 6 + 8：用授权码换 access_token，再用 token 取用户信息。
     *
     * @param code        认证系统回传的授权码（约 1 分钟有效）
     * @param redirectUri 与发起授权时完全一致的 redirect_uri
     * @return 用户身份
     * @throws SsoException 认证系统拒绝或返回结构异常时
     */
    @SuppressWarnings("unchecked")
    public SsoUser exchangeCode(String code, String redirectUri) {
        RestClient http = RestClient.builder().baseUrl(props.getBaseUrl()).build();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("redirect_uri", redirectUri);

        Map<String, Object> token;
        try {
            token = http.post()
                    .uri("/api/application/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("[sso] 换取令牌失败: {}", e.getMessage());
            throw new SsoException("统一认证换取令牌失败：" + e.getMessage());
        }
        if (token == null) {
            throw new SsoException("统一认证未返回令牌");
        }
        Object err = token.get("error");
        if (err != null) {
            throw new SsoException("统一认证拒绝：" + token.get("error_description"));
        }
        String accessToken = str(token.get("access_token"));
        if (accessToken == null || accessToken.isBlank()) {
            throw new SsoException("统一认证未返回 access_token");
        }

        Map<String, Object> data;
        try {
            MultiValueMap<String, String> single = new LinkedMultiValueMap<>();
            single.add("access_token", accessToken);
            data = http.post()
                    .uri("/api/application/oauth/data")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(single)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.warn("[sso] 获取用户信息失败: {}", e.getMessage());
            throw new SsoException("统一认证获取用户信息失败：" + e.getMessage());
        }
        if (data == null) {
            throw new SsoException("统一认证未返回用户信息");
        }
        Object derr = data.get("error");
        if (derr != null) {
            throw new SsoException("获取用户信息失败：" + data.get("error_description"));
        }
        return toUser(data);
    }

    /**
     * 接口登录（服务端直连，文档第 2 章）。
     * <p>
     * 本系统不启用。按文档要求，该接口只能由服务器发起，并须回传用户端 ip/ua。
     *
     * @param username    学工号
     * @param rawPassword 明文口令（内部按文档做 DES-CBC 加密）
     * @param clientIp    用户端 IP
     * @param userAgent   用户端 UA
     * @return 用户身份
     */
    @SuppressWarnings("unchecked")
    public SsoUser apiLogin(String username, String rawPassword, String clientIp, String userAgent) {
        RestClient http = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", desEncrypt(rawPassword, props.getClientSecret()));
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("ip", (clientIp == null || clientIp.isBlank()) ? "127.0.0.1" : clientIp);
        form.add("ua", (userAgent == null || userAgent.isBlank()) ? "AI-API-CENTER/1.0" : userAgent);

        Map<String, Object> res = http.post()
                .uri("/api/application/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (res == null) {
            throw new SsoException("统一认证无响应");
        }
        if (!"true".equalsIgnoreCase(String.valueOf(res.get("success")))) {
            Object ec = res.get("errorCode");
            String msg = str(res.get("error"));
            if (ec != null && "307".equals(String.valueOf(ec))) {
                Object d = res.get("data");
                String hint = (d instanceof Map<?, ?> dm) ? str(dm.get("forceButtonMsg")) : null;
                throw new SsoException(hint == null ? "需要先修改密码" : hint);
            }
            throw new SsoException(msg == null ? "统一认证登录失败" : msg);
        }
        Object d = res.get("data");
        if (!(d instanceof Map<?, ?>)) {
            throw new SsoException("统一认证返回结构异常");
        }
        return toUser((Map<String, Object>) d);
    }

    /**
     * 文档给出的参考实现：DES/CBC/PKCS5Padding，
     * 密钥与偏移量都取 client_secret 的前 8 字节（不足补 0x00，超出截断）。
     *
     * @param data 待加密数据（用户口令）
     * @param key  密钥（即 client_secret）
     * @return Base64 密文
     */
    public static String desEncrypt(String data, String key) {
        try {
            byte[] keyBytes = (key == null ? "" : key).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[8];
            if (keyBytes.length < 8) {
                System.arraycopy(keyBytes, 0, iv, 0, keyBytes.length);
            } else {
                System.arraycopy(keyBytes, 0, iv, 0, 8);
            }
            Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(iv, "DES"), new IvParameterSpec(iv));
            return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new SsoException("口令加密失败：" + e.getMessage());
        }
    }

    /**
     * 归一化认证系统回包。接口登录（第 2 章）与授权码取信息（第 8 章）字段名不完全一致，
     * 这里统一映射到 {@link SsoUser}。
     */
    private static SsoUser toUser(Map<String, Object> m) {
        String staffNo = firstNonNull(str(m.get("number")), str(m.get("uid")), str(m.get("account")));
        String name = str(m.get("name"));
        String college = firstNonNull(str(m.get("college")), str(m.get("eduPersonCollege")));
        String roleCode = str(m.get("role"));
        String roleName = str(m.get("roleName"));

        List<String> all = null;
        Object ar = m.get("allRole");
        if (ar instanceof List<?> list) {
            all = list.stream().map(SsoService::str).filter(v -> v != null).toList();
        } else {
            String arn = str(m.get("allRoleName"));
            if (arn != null && !arn.isBlank()) {
                all = List.of(arn.split(","));
            }
        }
        if (staffNo == null || staffNo.isBlank()) {
            throw new SsoException("统一认证未返回学工号");
        }
        return new SsoUser(staffNo, name, college, roleCode, roleName, all);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }

    /** 统一身份认证业务异常（消息可直接呈现给用户） */
    public static class SsoException extends RuntimeException {
        public SsoException(String message) {
            super(message);
        }
    }
}

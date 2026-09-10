package com.nora.common.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发期 {@code .env.local} 自动加载(经 spring.factories 注册,对全部服务生效)。
 *
 * <p>背景:密钥(embedding/LLM)按 README 约定放在 {@code nora-api/.env.local},
 * 但此前没有任何启动路径加载它——冷启动后 rag-service 拿不到
 * {@code NORA_EMBEDDING_API_KEY},RAG 检索静默降级为 "embedding not configured"。
 *
 * <p>查找顺序(cwd = 服务启动目录,均为 nora-api):
 * <ol>
 *   <li>{@code NORA_ENV_FILE} 显式指定的文件;</li>
 *   <li>{@code <cwd>/.env.local};</li>
 *   <li>{@code <cwd>/nora-api/.env.local}(从仓库根启动时兜底)。</li>
 * </ol>
 *
 * <p>优先级最低({@code addLast}),且真实环境变量/系统属性已定义的键直接跳过,
 * 生产环境变量永远压过本地开发文件。仅解析 {@code KEY=VALUE}(支持 export 前缀、
 * 单双引号、{@code #} 注释),不做变量展开;坏行忽略。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME_PREFIX = "noraDotenv:";

    private final Log log;

    /**
     * DeferredLogFactory:本处理器运行在日志系统初始化之前,直接 SLF4J 会被丢弃;
     * DeferredLog 缓冲消息,待 logging 就绪后回放(排障时能在启动日志里看到加载结果)。
     */
    public DotenvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DotenvEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = locate();
        if (file == null) {
            return;
        }
        Map<String, Object> added;
        try {
            added = apply(environment, file);
        } catch (IOException e) {
            log.warn("dotenv: cannot read " + file + " (" + e.getMessage() + ")");
            return;
        }
        log.info("dotenv: loaded " + added.size() + " key(s) from " + file
                + " (lowest precedence, real env vars win)");
    }

    /** 查找 .env.local:{@code NORA_ENV_FILE} > cwd/.env.local > cwd/nora-api/.env.local。 */
    static Path locate() {
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getenv("NORA_ENV_FILE");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Path.of(explicit.trim()));
        }
        String cwd = System.getProperty("user.dir", ".");
        candidates.add(Path.of(cwd, ".env.local"));
        candidates.add(Path.of(cwd, "nora-api", ".env.local"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * 解析并挂载为最低优先级属性源;真实环境变量/系统属性已存在的键跳过。
     *
     * @return 实际写入的键值(便于测试与日志)
     * @throws java.io.IOException 读文件失败(调用方用 DeferredLog 记录)
     */
    static Map<String, Object> apply(ConfigurableEnvironment environment, Path file) throws IOException {
        Map<String, Object> parsed = parse(file);
        parsed.keySet().removeIf(key -> System.getenv(key) != null || System.getProperty(key) != null);
        if (parsed.isEmpty()) {
            return Map.of();
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME_PREFIX + file, parsed));
        return parsed;
    }

    /** 解析 KEY=VALUE(export 前缀/引号/# 注释支持),不展开变量;坏行忽略。 */
    static Map<String, Object> parse(Path file) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean first = true;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw;
            if (first) {
                // 去 BOM:否则首行 key 带 \uFEFF 前缀匹配不上,首个变量被静默丢弃
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                first = false;
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (key.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                out.put(key, value);
            }
        }
        return out;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

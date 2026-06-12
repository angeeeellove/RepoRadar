package com.repordar.git;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从文件路径提取一级目录模块名。
 * <p>
 * 规则：跳过 src/ 前缀和包名前缀（com/org/net 等），取第一级业务目录名作为模块标识。
 * 顶层文件归入 {@code _root} 模块。
 *
 * @author frank
 */
@Component
public class ModuleDetector {

    private static final Set<String> PACKAGE_PREFIXES = Set.of(
            "com", "org", "net", "io", "cn", "uk", "de", "jp"
    );

    /**
     * 从文件路径提取模块名。
     *
     * @param filePath 文件路径（支持 / 和 \ 分隔符）
     * @return 模块名，顶层文件返回 "_root"
     */
    public String detectModule(String filePath) {
        String normalized = filePath.replace('\\', '/');

        // 去掉 src/ 前缀及其子目录
        String stripped = normalized.replaceFirst("^src/(main|test)/(java|kotlin|resources)/", "");
        stripped = stripped.replaceFirst("^src/", "");

        String[] parts = stripped.split("/");
        if (parts.length <= 1) {
            return "_root";
        }

        // 跳过包名前缀（com/org/net 等TLD + 紧跟的组织名）
        int start = 0;
        if (start < parts.length - 1 && PACKAGE_PREFIXES.contains(parts[start])) {
            start++; // 跳过 TLD (com/org/net)
            if (start < parts.length - 1) {
                start++; // 跳过组织名 (example/google/...)
            }
        }

        if (start < parts.length - 1) {
            return parts[start];
        }
        return parts[0];
    }

    /**
     * 从文件路径列表中提取所有不重复的模块名。
     *
     * @param filePaths 文件路径列表
     * @return 模块名集合
     */
    public Set<String> extractModules(List<String> filePaths) {
        return filePaths.stream()
                .map(this::detectModule)
                .collect(Collectors.toSet());
    }
}

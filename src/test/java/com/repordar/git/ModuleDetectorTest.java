package com.repordar.git;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModuleDetector 单元测试。
 *
 * @author frank
 */
class ModuleDetectorTest {

    private final ModuleDetector detector = new ModuleDetector();

    // ========== detectModule 测试 ==========

    @Test
    void shouldDetectFirstLevelDirectoryAsModule() {
        String path = "src/main/java/com/example/order/service/OrderService.java";
        assertEquals("order", detector.detectModule(path));
    }

    @Test
    void shouldDetectFromNonSrcPath() {
        String path = "payment/src/main/java/PaymentService.java";
        assertEquals("payment", detector.detectModule(path));
    }

    @Test
    void shouldReturnRootForTopLevelFile() {
        String path = "README.md";
        assertEquals("_root", detector.detectModule(path));
    }

    @Test
    void shouldSkipCommonPackagePrefixes() {
        String path = "src/main/java/com/example/user/UserService.java";
        assertEquals("user", detector.detectModule(path));
    }

    @Test
    void shouldSkipOrgPackagePrefix() {
        String path = "src/main/java/org/example/analytics/Report.java";
        assertEquals("analytics", detector.detectModule(path));
    }

    // ========== extractModules 测试 ==========

    @Test
    void shouldExtractAllModulesFromFileList() {
        List<String> paths = List.of(
                "src/main/java/com/example/order/OrderService.java",
                "src/main/java/com/example/user/UserService.java",
                "src/main/java/com/example/order/OrderRepo.java"
        );
        Set<String> modules = detector.extractModules(paths);
        assertEquals(Set.of("order", "user"), modules);
    }

    @Test
    void shouldReturnEmptySetForEmptyList() {
        Set<String> modules = detector.extractModules(List.of());
        assertTrue(modules.isEmpty());
    }

    @Test
    void shouldHandleWindowsPathSeparator() {
        String path = "src\\main\\java\\com\\example\\order\\Service.java";
        assertEquals("order", detector.detectModule(path));
    }
}

package com.repordar.anomaly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VagueScoringEngine 单元测试。
 * <p>
 * 满分 100，低于 50 判定模糊。
 * 减分：泛指词(-30)、缺少具体内容(-25)、描述过短(-20)、已知模糊模式(-25)
* 加分：引用模块名(+15)、引用 Issue 编号(+10)、引用错误码(+10)
 *
 * @author frank
 */
class VagueScoringEngineTest {

    private VagueScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new VagueScoringEngine(List.of("order", "payment"));
    }

    // ========== 模糊提交（应低于 50） ==========

    @Test
    void shouldFlagVagueGenericPhrase() {
        int score = engine.score("fix: 修复了订单相关的BUG");
        assertTrue(score < 50, "Score should be < 50 for vague message, got: " + score);
    }

    @Test
    void shouldHandleConventionalCommitsWithEmptyDescription() {
        int score = engine.score("fix:");
        assertTrue(score < 50, "Empty description after prefix should be vague");
    }

    @Test
    void shouldPenalizeTooShortDescription() {
        int score = engine.score("fix: 优化");
        assertTrue(score < 50, "Short description after prefix should be vague");
    }

    @Test
    void shouldPenalizeKnownVaguePatterns() {
        assertTrue(engine.score("优化了代码") < 50);
        assertTrue(engine.score("修复问题") < 50);
    }

    @Test
    void shouldHandleNullMessage() {
        assertEquals(0, engine.score(null));
    }

    @Test
    void shouldHandleBlankMessage() {
        assertEquals(0, engine.score("   "));
    }

    // ========== 清晰提交（应不低于 50） ==========

    @Test
    void shouldPassClearSpecificMessage() {
        int score = engine.score("fix: resolve NullPointerException in OrderService.createOrder when payment timeout");
        assertTrue(score >= 50, "Score should be >= 50 for clear message, got: " + score);
    }

    @Test
    void shouldNotPenalizeTypePrefix() {
        int score = engine.score("fix: resolve race condition in payment flow");
        assertTrue(score >= 50, "fix prefix should not cause vagueness");
    }

    // ========== 加分项 ==========

    @Test
    void shouldBonusForModuleReference() {
        int score = engine.score("feat: add order cancellation endpoint");
        assertTrue(score >= 60, "Should get bonus for referencing module name, got: " + score);
    }

    @Test
    void shouldBonusForIssueRef() {
        int score = engine.score("fix: fix login timeout #123");
        assertTrue(score >= 60, "Should get bonus for Issue reference, got: " + score);
    }

    @Test
    void shouldBonusForErrorCode() {
        int score = engine.score("fix: handle NullPointerException in user service");
        assertTrue(score >= 60, "Should get bonus for error code, got: " + score);
    }

    // ========== generateReason 测试 ==========

    @Test
    void shouldGenerateReasonForVagueMessage() {
        int score = engine.score("fix: 修复了订单相关的BUG");
        String reason = engine.generateReason("fix: 修复了订单相关的BUG", score);
        assertNotNull(reason);
        assertFalse(reason.isEmpty());
    }

    @Test
    void shouldReturnClearForHighScore() {
        String reason = engine.generateReason("fix: resolve race condition", 80);
        assertEquals("提交信息基本清晰", reason);
    }
}

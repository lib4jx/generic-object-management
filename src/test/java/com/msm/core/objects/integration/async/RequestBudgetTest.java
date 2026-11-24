package com.msm.core.objects.integration.async;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** {@code async.request-budget-ms} (2.7.8): the wait left to a call within its request. */
class RequestBudgetTest {

    private static final long INLINE_WAIT_MS = 20_000L;
    private static final long BUDGET_MS = 27_000L;
    private static final long START_NANOS = 1_000L;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void elapsedIsTheTimeSinceTheRequestArrived() {
        // Arrange
        RequestBudget budget = new RequestBudget(() -> START_NANOS + TimeUnit.MILLISECONDS.toNanos(9_000), () -> START_NANOS);

        // Act / Assert
        assertThat(budget.elapsedMs()).isEqualTo(9_000L);
    }

    @Test
    void outsideARequestThereIsNoElapsedTime() {
        // Act / Assert
        assertThat(new RequestBudget(System::nanoTime, () -> null).elapsedMs()).isNull();
        assertThat(new RequestBudget().elapsedMs()).isNull();
    }

    @Test
    void aFastRequestWaitsTheWholeInlineWait() {
        // Act / Assert: 2 s spent, 25 s left, capped at 20 s
        assertThat(new RequestBudget().waitMs(INLINE_WAIT_MS, BUDGET_MS, 2_000L)).isEqualTo(INLINE_WAIT_MS);
    }

    @Test
    void aSlowRequestWaitsOnlyWhatIsLeftOfItsBudget() {
        // Act / Assert: 22 s spent before the call (dev: WMS 504 after 21 s), 5 s left
        assertThat(new RequestBudget().waitMs(INLINE_WAIT_MS, BUDGET_MS, 22_000L)).isEqualTo(5_000L);
    }

    @Test
    void aRequestPastItsBudgetHasNoTimeLeft() {
        // Act / Assert
        assertThat(new RequestBudget().waitMs(INLINE_WAIT_MS, BUDGET_MS, BUDGET_MS)).isZero();
        assertThat(new RequestBudget().waitMs(INLINE_WAIT_MS, BUDGET_MS, 28_000L)).isNegative();
    }

    @Test
    void withoutABudgetOrARequestTheWaitIsTheInlineWait() {
        // Act / Assert
        assertThat(new RequestBudget().waitMs(INLINE_WAIT_MS, null, 28_000L)).isEqualTo(INLINE_WAIT_MS);
        assertThat(new RequestBudget().waitMs(INLINE_WAIT_MS, BUDGET_MS, null)).isEqualTo(INLINE_WAIT_MS);
    }

    @Test
    void theFilterMarksTheArrivalThatTheBudgetReads() throws Exception {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/order/api/v1/cn/portal/generic/objects/v2order/1/submitorder");
        FilterChain chain = mock(FilterChain.class);
        long before = System.nanoTime();

        // Act
        new RequestStartFilter().doFilter(request, new MockHttpServletResponse(), chain);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // Assert
        Object startNanos = request.getAttribute(RequestStartFilter.START_NANOS_ATTRIBUTE);
        assertThat(startNanos).isInstanceOf(Long.class);
        assertThat((Long) startNanos).isGreaterThanOrEqualTo(before);
        assertThat(new RequestBudget().elapsedMs()).isNotNull().isNotNegative();
        verify(chain).doFilter(eq(request), any());
    }
}

package com.mutualfund.analytics.api;

import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.Fund;
import com.mutualfund.analytics.model.NAVRecord;
import com.mutualfund.analytics.model.SyncState;
import com.mutualfund.analytics.service.FundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(FundController.class)
class FundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FundService fundService;

    // ── Fund list endpoint ────────────────────────────────────────────────────

    @Test
    void testGetAllFunds_NoFilter_ReturnsListWithCount() throws Exception {
        List<Fund> funds = List.of(
            Fund.builder().code("119598").name("Axis Midcap Direct").amc("Axis").category("Equity").build(),
            Fund.builder().code("120586").name("ICICI Midcap Direct").amc("ICICI").category("Equity").build()
        );
        // No query params → controller calls getAllFunds()
        when(fundService.getAllFunds()).thenReturn(funds);

        mockMvc.perform(get("/api/v1/funds"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.funds[0].fund_code").value("119598"));
    }

    @Test
    void testGetAllFunds_WithCategoryFilter_CallsFilteredMethod() throws Exception {
        List<Fund> funds = List.of(
            Fund.builder().code("119598").name("Axis Midcap Direct").amc("Axis")
                .category("Equity: Mid Cap Direct Growth").build()
        );
        when(fundService.getAllFundsFiltered("Mid Cap", null)).thenReturn(funds);

        mockMvc.perform(get("/api/v1/funds").param("category", "Mid Cap"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.filter_category").value("Mid Cap"));
    }

    @Test
    void testGetAllFunds_WithAmcFilter_CallsFilteredMethod() throws Exception {
        List<Fund> funds = List.of(
            Fund.builder().code("118989").name("HDFC Midcap Direct").amc("HDFC Mutual Fund")
                .category("Equity: Mid Cap Direct Growth").build()
        );
        when(fundService.getAllFundsFiltered(null, "HDFC")).thenReturn(funds);

        mockMvc.perform(get("/api/v1/funds").param("amc", "HDFC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.filter_amc").value("HDFC"));
    }

    // ── Fund detail endpoint ──────────────────────────────────────────────────

    @Test
    void testGetFund_ExistingCode_Returns200WithLatestNAV() throws Exception {
        Fund fund = Fund.builder()
            .code("119598").name("Axis Midcap Direct").amc("Axis").category("Equity").build();
        NAVRecord latestNav = NAVRecord.builder()
            .fundCode("119598").date("2026-03-15").nav(105.5).build();

        when(fundService.getFundByCode("119598")).thenReturn(Optional.of(fund));
        when(fundService.getLatestNAV("119598")).thenReturn(Optional.of(latestNav));

        mockMvc.perform(get("/api/v1/funds/119598"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fund_code").value("119598"))
            .andExpect(jsonPath("$.fund_name").value("Axis Midcap Direct"))
            .andExpect(jsonPath("$.current_nav").value(105.5))
            .andExpect(jsonPath("$.last_updated").value("2026-03-15"));
    }

    @Test
    void testGetFund_UnknownCode_Returns404() throws Exception {
        when(fundService.getFundByCode("UNKNOWN")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/funds/UNKNOWN"))
            .andExpect(status().isNotFound());
    }

    // ── Analytics endpoints ───────────────────────────────────────────────────

    @Test
    void testGetAnalytics_NoWindow_ReturnsLegacyFormat() throws Exception {
        Fund fund = Fund.builder().code("119598").name("Axis Midcap Direct").amc("Axis").category("Equity").build();
        when(fundService.getFundByCode("119598")).thenReturn(Optional.of(fund));

        AnalyticsResult ar = AnalyticsResult.builder()
            .fundCode("119598").window("3Y")
            .cagrMedian(15.2).cagrMin(8.1).cagrMax(28.5)
            .rollingMin(8.1).rollingMax(28.5).rollingMedian(15.2)
            .maxDrawdown(-35.0).rollingPeriodsAnalyzed(500)
            .computedAt(LocalDateTime.now())
            .build();

        when(fundService.getAnalytics("119598")).thenReturn(List.of(ar));

        mockMvc.perform(get("/api/v1/funds/119598/analytics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fund_code").value("119598"))
            .andExpect(jsonPath("$.analytics[0].window").value("3Y"))
            .andExpect(jsonPath("$.analytics[0].cagr_median").value(15.2));
    }

    @Test
    void testGetAnalytics_WithWindow_ReturnsSpecFormat() throws Exception {
        Fund fund = Fund.builder()
            .code("119598").name("Axis Midcap Direct").amc("Axis").category("Equity: Mid Cap").build();
        AnalyticsResult ar = AnalyticsResult.builder()
            .fundCode("119598").window("3Y")
            .startDate("2016-01-15").endDate("2026-01-06")
            .totalDays(3644).navDataPoints(2513)
            .cagrMedian(22.3).cagrMin(9.5).cagrMax(45.2)
            .rollingMin(8.2).rollingMax(48.5).rollingMedian(22.3)
            .rollingP25(15.7).rollingP75(28.9)
            .maxDrawdown(-32.1).rollingPeriodsAnalyzed(731)
            .computedAt(LocalDateTime.now())
            .build();

        when(fundService.getFundByCode("119598")).thenReturn(Optional.of(fund));
        when(fundService.getAnalyticsForWindow("119598", "3Y")).thenReturn(Optional.of(ar));

        mockMvc.perform(get("/api/v1/funds/119598/analytics").param("window", "3Y"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fund_code").value("119598"))
            .andExpect(jsonPath("$.window").value("3Y"))
            .andExpect(jsonPath("$.data_availability.start_date").value("2016-01-15"))
            .andExpect(jsonPath("$.data_availability.nav_data_points").value(2513))
            .andExpect(jsonPath("$.rolling_returns.median").value(22.3))
            .andExpect(jsonPath("$.max_drawdown").value(-32.1))
            .andExpect(jsonPath("$.cagr.median").value(22.3));
    }

    @Test
    void testGetAnalytics_InvalidWindow_Returns400() throws Exception {
        Fund fund = Fund.builder().code("119598").name("Axis").amc("Axis").category("Equity").build();
        when(fundService.getFundByCode("119598")).thenReturn(Optional.of(fund));

        mockMvc.perform(get("/api/v1/funds/119598/analytics").param("window", "2Y"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testGetAnalytics_NoData_ReturnsEmptyWithMessage() throws Exception {
        Fund fund = Fund.builder().code("119598").name("Axis").amc("Axis").category("Equity").build();
        when(fundService.getFundByCode("119598")).thenReturn(Optional.of(fund));
        when(fundService.getAnalytics("119598")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/funds/119598/analytics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.analytics").isEmpty());
    }

    // ── Ranking endpoints ─────────────────────────────────────────────────────

    @Test
    void testFundsRank_ValidParams_ReturnsSpecFormat() throws Exception {
        FundService.RankedFund rf1 = new FundService.RankedFund(
            1, "119598", "Axis Midcap Direct", "Axis", 22.3, -32.1, 78.45, "2026-01-06");
        FundService.RankedFund rf2 = new FundService.RankedFund(
            2, "118989", "HDFC Mid-Cap Opportunities", "HDFC", 21.8, -29.5, 92.31, "2026-01-06");
        FundService.RankingResponse response = new FundService.RankingResponse(
            "Mid Cap", "3Y", "median_return", 5, 2, List.of(rf1, rf2));

        when(fundService.rankFunds("Mid Cap", "3Y", "median_return", 5)).thenReturn(response);

        mockMvc.perform(get("/api/v1/funds/rank")
                .param("category", "Mid Cap")
                .param("window",   "3Y"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("Mid Cap"))
            .andExpect(jsonPath("$.window").value("3Y"))
            .andExpect(jsonPath("$.sorted_by").value("median_return"))
            .andExpect(jsonPath("$.funds[0].rank").value(1))
            .andExpect(jsonPath("$.funds[0].fund_code").value("119598"))
            .andExpect(jsonPath("$.funds[0].median_return_3y").value(22.3))
            .andExpect(jsonPath("$.funds[0].max_drawdown_3y").value(-32.1))
            .andExpect(jsonPath("$.funds[0].current_nav").value(78.45));
    }

    @Test
    void testFundsRank_InvalidWindow_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/funds/rank")
                .param("category", "Mid Cap")
                .param("window",   "2Y"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testGetRanking_LegacyEndpoint_ReturnsRankedFunds() throws Exception {
        AnalyticsResult r1 = AnalyticsResult.builder()
            .fundCode("119598").window("3Y").cagrMedian(18.0).build();
        AnalyticsResult r2 = AnalyticsResult.builder()
            .fundCode("120586").window("3Y").cagrMedian(14.0).build();
        when(fundService.rankFundsByWindow("3Y")).thenReturn(List.of(r1, r2));

        mockMvc.perform(get("/api/v1/analytics/ranking?window=3Y"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.window").value("3Y"))
            .andExpect(jsonPath("$.count").value(2))
            .andExpect(jsonPath("$.ranked_funds[0].cagr_median").value(18.0));
    }

    // ── Sync endpoints ────────────────────────────────────────────────────────

    @Test
    void testTriggerSync_WhenNotRunning_ReturnsStarted() throws Exception {
        FundService.SyncPipelineStatus status = new FundService.SyncPipelineStatus(
            false, 0, 0, 10, null);
        when(fundService.getPipelineStatus()).thenReturn(status);
        doNothing().when(fundService).triggerSync();

        mockMvc.perform(post("/api/v1/sync/trigger"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("started"));
    }

    @Test
    void testTriggerSync_WhenAlreadyRunning_ReturnsAlreadyRunning() throws Exception {
        FundService.SyncPipelineStatus status = new FundService.SyncPipelineStatus(
            true, 3, 0, 10, LocalDateTime.now());
        when(fundService.getPipelineStatus()).thenReturn(status);

        mockMvc.perform(post("/api/v1/sync/trigger"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("already_running"));
    }

    @Test
    void testGetSyncStatus_ReturnsProgressAndFundStates() throws Exception {
        FundService.SyncPipelineStatus pipelineStatus = new FundService.SyncPipelineStatus(
            false, 10, 0, 10, LocalDateTime.now());
        when(fundService.getPipelineStatus()).thenReturn(pipelineStatus);

        SyncState s = SyncState.builder()
            .fundCode("119598").status("completed")
            .lastSyncedDate("2026-03-15").updatedAt(LocalDateTime.now()).build();
        when(fundService.getSyncStatus()).thenReturn(List.of(s));

        mockMvc.perform(get("/api/v1/sync/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pipeline.running").value(false))
            .andExpect(jsonPath("$.pipeline.completed").value(10))
            .andExpect(jsonPath("$.funds[0].fund_code").value("119598"));
    }

    // ── Metrics endpoint ──────────────────────────────────────────────────────

    @Test
    void testGetMetrics_ReturnsJVMAndPipelineData() throws Exception {
        FundService.SyncPipelineStatus status = new FundService.SyncPipelineStatus(
            false, 0, 0, 0, null);
        when(fundService.getPipelineStatus()).thenReturn(status);
        when(fundService.getAllFunds()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jvm").exists())
            .andExpect(jsonPath("$.pipeline").exists())
            .andExpect(jsonPath("$.version").value("1.1.0"));
    }
}

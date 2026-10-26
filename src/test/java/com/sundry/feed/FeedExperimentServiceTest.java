package com.sundry.feed;

import com.sundry.feed.dto.FeedExperimentView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedExperimentServiceTest {

    @Mock
    private FeedMetricsMapper mapper;

    @Test
    void bucketIsStableAndRoughlyMatchesPercent() {
        FeedProperties properties = new FeedProperties();
        properties.setExperimentControlPercent(10);
        FeedExperimentService service = new FeedExperimentService(properties, mapper);

        assertThat(service.bucket(42L)).isEqualTo(service.bucket(42L));
        int control = 0;
        for (long userId = 1; userId <= 10_000; userId++) {
            if (service.bucket(userId) < 10) {
                control++;
            }
        }
        assertThat(control).isBetween(800, 1200);
    }

    @Test
    void controlBuildIdHasExpectedPrefix() {
        FeedExperimentService service = new FeedExperimentService(
                new FeedProperties(), mapper);
        assertThat(service.controlBuildId(99L))
                .startsWith("RULE:99:")
                .hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void wilsonIntervalIsSane() {
        FeedExperimentService service = new FeedExperimentService(
                new FeedProperties(), mapper);

        FeedExperimentService.Wilson value = service.wilson(10, 100);

        assertThat(value.ctr()).isEqualTo(0.1);
        assertThat(value.lower()).isCloseTo(0.0552, org.assertj.core.data.Offset.offset(0.001));
        assertThat(value.upper()).isCloseTo(0.1744, org.assertj.core.data.Offset.offset(0.001));
        assertThat(value.lower()).isLessThan(value.ctr());
        assertThat(value.upper()).isGreaterThan(value.ctr());
        assertThat(service.wilson(0, 0).ctr()).isZero();
    }

    @Test
    void reportBuildsSidesAndLift() {
        FeedMetricsMapper.ExperimentCount control = new FeedMetricsMapper.ExperimentCount();
        control.setGrp("CONTROL");
        control.setExposures(100L);
        control.setClicks(10L);
        FeedMetricsMapper.ExperimentCount ai = new FeedMetricsMapper.ExperimentCount();
        ai.setGrp("AI");
        ai.setExposures(100L);
        ai.setClicks(20L);
        when(mapper.countExperimentEvents(any())).thenReturn(List.of(control, ai));

        FeedExperimentView view = new FeedExperimentService(
                new FeedProperties(), mapper).report(7);

        assertThat(view.control().exposures()).isEqualTo(100);
        assertThat(view.control().clicks()).isEqualTo(10);
        assertThat(view.control().ctr()).isEqualTo(0.1);
        assertThat(view.ai().ctr()).isEqualTo(0.2);
        assertThat(view.lift()).isEqualTo(1.0);
        assertThat(view.control().lower()).isLessThan(view.control().upper());
    }
}

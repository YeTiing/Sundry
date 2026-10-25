package com.sundry.feed;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedEventCleanupProcessorTest {

    private final FeedEventMapper mapper = mock(FeedEventMapper.class);
    private final FeedProperties properties = new FeedProperties();

    private FeedEventCleanupProcessor processor() {
        properties.setEventCleanupBatchSize(2);
        properties.setEventCleanupMaxBatches(3);
        return new FeedEventCleanupProcessor(mapper, properties);
    }

    @Test
    void sumsBatchesAndStopsAtMaxBatches() {
        when(mapper.deleteOld(eq("EXPOSURE"), any(LocalDateTime.class), anyInt()))
                .thenReturn(2, 2, 1);
        when(mapper.deleteOld(eq("CLICK"), any(LocalDateTime.class), anyInt()))
                .thenReturn(2, 0);

        FeedEventCleanupProcessor.CleanupResult result = processor().cleanup();

        assertThat(result.exposures()).isEqualTo(5);
        assertThat(result.clicks()).isEqualTo(2);
        verify(mapper, times(3))
                .deleteOld(eq("EXPOSURE"), any(LocalDateTime.class), eq(2));
        verify(mapper, times(2))
                .deleteOld(eq("CLICK"), any(LocalDateTime.class), eq(2));
    }

    @Test
    void stopsWhenBatchIsNotFull() {
        when(mapper.deleteOld(eq("EXPOSURE"), any(LocalDateTime.class), anyInt()))
                .thenReturn(1);
        when(mapper.deleteOld(eq("CLICK"), any(LocalDateTime.class), anyInt()))
                .thenReturn(0);

        FeedEventCleanupProcessor.CleanupResult result = processor().cleanup();

        assertThat(result.exposures()).isEqualTo(1);
        assertThat(result.clicks()).isZero();
        verify(mapper, times(1))
                .deleteOld(eq("EXPOSURE"), any(LocalDateTime.class), eq(2));
        verify(mapper, times(1))
                .deleteOld(eq("CLICK"), any(LocalDateTime.class), eq(2));
    }
}

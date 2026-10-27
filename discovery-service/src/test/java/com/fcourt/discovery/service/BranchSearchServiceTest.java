package com.fcourt.discovery.service;

import com.fcourt.discovery.dto.request.BranchSearchRequest;
import com.fcourt.discovery.dto.response.BranchSearchResponse;
import com.fcourt.discovery.service.BranchSearchService;
import com.fcourt.discovery.model.event.BranchProjectionChanged;
import com.fcourt.discovery.repository.BranchSearchRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BranchSearchServiceTest {
    private final BranchSearchRequest request = new BranchSearchRequest(null, null, null, null,
            null, null, null, null, null, null);

    @Test
    void cachesRepeatedQueriesButDoesNotCacheFailures() {
        var repository = mock(BranchSearchRepository.class);
        var response = new BranchSearchResponse(List.of(), 0, 20, 0, 0);
        when(repository.search(request)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(response);
        var service = new BranchSearchService(repository, 5);
        assertThatThrownBy(() -> service.search(request)).isInstanceOf(IllegalStateException.class);
        assertThat(service.search(request)).isEqualTo(response);
        assertThat(service.search(request)).isEqualTo(response);
        verify(repository, times(2)).search(request);
    }

    @Test
    void anOldInflightReadCannotPopulateTheNewRevision() throws Exception {
        var repository = mock(BranchSearchRepository.class);
        var oldResult = new BranchSearchResponse(List.of(), 0, 20, 0, 0);
        var newResult = new BranchSearchResponse(List.of(), 0, 20, 1, 1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(repository.search(request)).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("read timed out");
            return oldResult;
        }).thenReturn(newResult);
        var service = new BranchSearchService(repository, 5);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inflight = executor.submit(() -> service.search(request));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            service.projectionChanged(new BranchProjectionChanged(1));
            assertThat(service.search(request)).isEqualTo(newResult);
            release.countDown();
            assertThat(inflight.get(5, TimeUnit.SECONDS)).isEqualTo(oldResult);
            assertThat(service.search(request)).isEqualTo(newResult);
            verify(repository, times(2)).search(request);
        } finally {
            release.countDown();
        }
    }
}

package ai.dvine.research;

import org.springframework.stereotype.Component;

/** No network client is configured in this build. The phone does its own lookup. */
@Component
public class OfflinePageSource implements ResearchService.PageSource {
    @Override
    public String fetch(String url) {
        return null;
    }
}

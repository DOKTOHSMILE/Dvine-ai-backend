package ai.dvine.semantics;

import ai.dvine.research.ResearchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SemanticConfiguration {
    @Bean
    SemanticInterpretationService semanticInterpretationService() {
        return new SemanticInterpretationService();
    }

    @Bean
    ResearchService researchService(ResearchService.PageSource source) {
        return new ResearchService(source);
    }
}

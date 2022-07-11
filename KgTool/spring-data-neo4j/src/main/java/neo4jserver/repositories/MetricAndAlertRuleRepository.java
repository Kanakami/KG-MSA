package neo4jserver.repositories;

import neo4jserver.domain.relationships.MetricAndAlertRules;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.Optional;

public interface MetricAndAlertRuleRepository extends Neo4jRepository<MetricAndAlertRules, Long> {
    Optional<MetricAndAlertRules> findById(String id);
}

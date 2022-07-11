package neo4jserver.repositories;

import neo4jserver.domain.MetricResult;
import neo4jserver.domain.entities.AlertRule;
import neo4jserver.domain.entities.Metric;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.Optional;

public interface AlertRuleRepository extends Neo4jRepository<AlertRule, Long> {
    Optional<AlertRule> findById(String id);

    @Query("MATCH (n:AlertRule) where n.name=$name RETURN n")
    Optional<AlertRule> findByName(@Param("name") String name);

    @Query("MATCH (n:AlertRule) return n")
    ArrayList<AlertRule> findAllAlertRules();
}

package graphapp.repositories;

import graphapp.domain.entities.ServiceApiMetric;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.ArrayList;
import java.util.Optional;

public interface MetricOfServiceApiRepository extends Neo4jRepository<ServiceApiMetric, Long> {

    @Query("MATCH (n:ServiceApiMetric) where n.id={0} RETURN n")
    Optional<ServiceApiMetric> findById(String id);

    @Query("MATCH (n:ServiceApiMetric) return n")
    ArrayList<ServiceApiMetric> findAllMetrics();

}

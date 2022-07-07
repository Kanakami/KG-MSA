package neo4jserver.repositories;

import neo4jserver.domain.entities.PodMetric;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MetricOfPodRepository extends Neo4jRepository<PodMetric,Long>{

    @Query("MATCH (n:PodMetric) where n.id=$id RETURN n")
    Optional<PodMetric> findById(@Param("id") String id);

}

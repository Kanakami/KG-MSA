package neo4jserver.repositories;

import neo4jserver.domain.relationships.TraceInvokeApiToApi;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TraceInvokeApiToApiRepository extends Neo4jRepository<TraceInvokeApiToApi, Long> {

    @Query("MATCH p=()-[r:TraceInvokeApiToApi]->() WHERE r.id=$id RETURN p")
    Optional<TraceInvokeApiToApi> findById(@Param("id") String id);
}

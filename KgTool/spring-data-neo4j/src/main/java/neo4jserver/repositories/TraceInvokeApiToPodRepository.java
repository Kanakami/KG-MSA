package neo4jserver.repositories;

import neo4jserver.domain.relationships.TraceInvokeApiToPod;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.Optional;

public interface TraceInvokeApiToPodRepository extends Neo4jRepository<TraceInvokeApiToPod, Long> {

    @Query("MATCH p=()-[r:TraceInvokeApiToPod]->() WHERE r.id=$id RETURN p")
    Optional<TraceInvokeApiToPod> findById(@Param("id") String id);

}

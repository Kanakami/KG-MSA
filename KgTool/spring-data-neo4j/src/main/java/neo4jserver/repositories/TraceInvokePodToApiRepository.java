package neo4jserver.repositories;

import neo4jserver.domain.relationships.TraceInvokePodToApi;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.Optional;

public interface TraceInvokePodToApiRepository extends Neo4jRepository<TraceInvokePodToApi, Long> {

    @Query("MATCH p=()-[r:TraceInvokePodToApi]->() WHERE r.id=$id RETURN p")
    Optional<TraceInvokePodToApi> findById(@Param("id") String id);

}

package neo4jserver.repositories;

import neo4jserver.domain.relationships.MetricAndVirtualMachine;
import org.springframework.data.neo4j.annotation.Query;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.ArrayList;
import java.util.Optional;

public interface MetricAndVirtualMachineRepository extends Neo4jRepository<MetricAndVirtualMachine, Long> {
    Optional<MetricAndVirtualMachine> findById(String id);

    @Query("MATCH p=()-[r:MetricAndVirtualMachine]->() RETURN p")
    ArrayList<MetricAndVirtualMachine> findAllMetricAndVirtualMachine();
}

package neo4jserver.domain.relationships;

import lombok.Getter;
import lombok.Setter;
import neo4jserver.domain.entities.Metric;
import neo4jserver.domain.entities.VirtualMachine;
import org.neo4j.ogm.annotation.EndNode;
import org.neo4j.ogm.annotation.RelationshipEntity;
import org.neo4j.ogm.annotation.StartNode;

@Getter
@Setter
@RelationshipEntity(type = "MetricAndVirtualMachine")
public class MetricAndVirtualMachine extends BasicRelationship{

    @StartNode
    private Metric metric;

    @EndNode
    private VirtualMachine virtualMachine;

    public MetricAndVirtualMachine(){
        super();
    }

}

package neo4jserver.domain.relationships;

import lombok.Getter;
import lombok.Setter;
import neo4jserver.domain.entities.AlertRule;
import neo4jserver.domain.entities.Metric;
import org.neo4j.ogm.annotation.EndNode;
import org.neo4j.ogm.annotation.RelationshipEntity;
import org.neo4j.ogm.annotation.StartNode;

@Getter
@Setter
@RelationshipEntity(type="MetricAndAlertRules")
public class MetricAndAlertRules extends BasicRelationship{
    @StartNode
    private AlertRule alertRule;

    @EndNode
    private Metric metric;

    public MetricAndAlertRules(){
        super();
    }
}

package neo4jserver.domain.entities;

import lombok.Getter;
import lombok.Setter;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Property;

import java.util.ArrayList;
@Setter
@Getter
@NodeEntity(label="AlertRule")
public class AlertRule extends GraphNode{
    @Property(name="expr")
    private String expr;
    @Property(name="duration")
    private String duration;
    @Property(name = "severity")
    private String severity;

    public AlertRule(){
        super();
    }
}

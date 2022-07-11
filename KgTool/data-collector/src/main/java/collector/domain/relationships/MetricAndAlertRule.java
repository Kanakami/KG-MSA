package collector.domain.relationships;

import collector.domain.entities.AlertRule;
import collector.domain.entities.Metric;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MetricAndAlertRule extends BasicRelationship {
    private Metric metric;

    private AlertRule alertRule;

    public MetricAndAlertRule(){
        super();
    }
    public MetricAndAlertRule(Metric metric, AlertRule alertRule, String id, String relation){
        super();
        this.setMetric(metric);
        this.setAlertRule(alertRule);
        this.setId(id);
        this.setRelation(relation);
    }
}

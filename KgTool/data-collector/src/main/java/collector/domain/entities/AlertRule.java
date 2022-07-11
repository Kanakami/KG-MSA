package collector.domain.entities;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;

@Setter
@Getter
public class AlertRule extends GraphNode{
    private String expr;
    private ArrayList<MetricSearch> metric;
    private String duration;
    private String severity;
    public AlertRule(){
        super();
        this.setMetric(new ArrayList<>());
    }
    public AlertRule(String id, String name, String expr, ArrayList<MetricSearch> metricName, String duration, String severity){
        this.setId(id);
        this.setName(name);
        this.setExpr(expr);
        this.setMetric(metric);
        this.setDuration(duration);
        this.setSeverity(severity);
    }
}

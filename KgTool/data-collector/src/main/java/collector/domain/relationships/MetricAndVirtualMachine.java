package collector.domain.relationships;

import collector.domain.entities.Metric;
import collector.domain.entities.VirtualMachine;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
public class MetricAndVirtualMachine extends BasicRelationship{
    private Metric metric;
    private VirtualMachine virtualMachine;

    public MetricAndVirtualMachine(){
        super();
    }

    public MetricAndVirtualMachine(Metric metric, VirtualMachine virtualMachine, String id, String relation){
        super();
        this.setMetric(metric);
        this.setVirtualMachine(virtualMachine);
        this.setId(id);
        this.setRelation(relation);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MetricAndVirtualMachine)) return false;
        if (!super.equals(o)) return false;
        MetricAndVirtualMachine that = (MetricAndVirtualMachine) o;
        return Objects.equals(metric, that.metric) &&
                Objects.equals(virtualMachine, that.virtualMachine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), metric, virtualMachine);
    }
}

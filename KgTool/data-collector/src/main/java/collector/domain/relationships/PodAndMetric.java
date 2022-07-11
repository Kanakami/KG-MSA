package collector.domain.relationships;

import collector.domain.entities.Metric;
import collector.domain.entities.Pod;
import lombok.AllArgsConstructor;

import java.util.Objects;
@AllArgsConstructor
public class PodAndMetric extends BasicRelationship  {

    private Metric podMetric;

    private Pod pod;

    public PodAndMetric() {
        super();
    }

    public PodAndMetric(Metric podMetric, Pod pod, String id, String relation){
        super();
        this.setPodMetric(podMetric);
        this.setPod(pod);
        this.setId(id);
        this.setRelation(relation);
    }

    public Metric getPodMetric() {
        return podMetric;
    }

    public void setPodMetric(Metric podMetric) {
        this.podMetric = podMetric;
    }

    public Pod getPod() {
        return pod;
    }

    public void setPod(Pod pod) {
        this.pod = pod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PodAndMetric)) return false;
        if (!super.equals(o)) return false;
        PodAndMetric that = (PodAndMetric) o;
        return Objects.equals(podMetric, that.podMetric) &&
                Objects.equals(pod, that.pod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), podMetric, pod);
    }
}

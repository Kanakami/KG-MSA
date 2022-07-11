package collector.domain.entities;

public class Metric extends BasicMetric{

    public Metric() {
        super();
    }
    public Metric(String id, String name, String creationTimestamp, String latestUpdateTimestamp){
        this.setId(id);
        this.setName(name);
        this.setCreationTimestamp(creationTimestamp);
        this.setLatestUpdateTimestamp(latestUpdateTimestamp);
    }

}

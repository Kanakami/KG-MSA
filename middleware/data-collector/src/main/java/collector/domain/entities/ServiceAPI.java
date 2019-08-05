package collector.domain.entities;

public class ServiceAPI extends GraphNode {

    //serviceAPI的ID和name都是其api名称,例如/ticketinfo/queryForStationId
    //Example "/ticketinfo/queryForStationId" -> name
    //Example "ts-ticketinfo-service" -> hostname
    private String hostName;

    private double abnormality;

    public ServiceAPI() {
        super();
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public double getAbnormality() {
        return abnormality;
    }

    public void setAbnormality(double abnormality) {
        this.abnormality = abnormality;
    }
}

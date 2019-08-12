package graphapp.domain.relationships;

import graphapp.domain.entities.Pod;
import graphapp.domain.entities.ServiceAPI;
import org.neo4j.ogm.annotation.*;

import java.util.HashSet;

@RelationshipEntity(type = "TraceInvokePodToApi")
public class TraceInvokePodToApi {

    @Id
    private String id;

    @StartNode
    private Pod pod;

    @EndNode
    private ServiceAPI serviceAPI;

    @Property(name="relation")
    private String relation;

    @Property(name="traceIdSpanId")
    private HashSet<String> traceIdAndSpanIds;

    @Property(name="className")
    private String className = this.getClass().toString();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Pod getPod() {
        return pod;
    }

    public void setPod(Pod pod) {
        this.pod = pod;
    }

    public ServiceAPI getServiceAPI() {
        return serviceAPI;
    }

    public void setServiceAPI(ServiceAPI serviceAPI) {
        this.serviceAPI = serviceAPI;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public HashSet<String> getTraceIdAndSpanIds() {
        return traceIdAndSpanIds;
    }

    public void setTraceIdAndSpanIds(HashSet<String> traceIdAndSpanIds) {
        this.traceIdAndSpanIds = traceIdAndSpanIds;
    }
}
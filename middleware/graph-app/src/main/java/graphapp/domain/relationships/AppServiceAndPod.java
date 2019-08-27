package graphapp.domain.relationships;

import graphapp.domain.entities.AppService;
import graphapp.domain.entities.Pod;
import org.neo4j.ogm.annotation.*;

import java.util.Objects;

@RelationshipEntity(type = "AppServiceAndPod")
public class AppServiceAndPod extends BasicRelationship {

    @Id
    private String id;

    @StartNode
    private Pod pod;

    @EndNode
    private AppService appService;

    @Property(name="relation")
    private String relation;

    @Property(name="className")
    private String className = this.getClass().toString();

    public AppServiceAndPod() {
    }

    public AppServiceAndPod(Pod pod, AppService appService, String relation) {
        this.pod = pod;
        this.appService = appService;
        this.relation = relation;
    }

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

    public AppService getAppService() {
        return appService;
    }

    public void setAppService(AppService appService) {
        this.appService = appService;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppServiceAndPod)) return false;
        AppServiceAndPod that = (AppServiceAndPod) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(pod, that.pod) &&
                Objects.equals(appService, that.appService) &&
                Objects.equals(relation, that.relation) &&
                Objects.equals(className, that.className);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, pod, appService, relation, className);
    }
}

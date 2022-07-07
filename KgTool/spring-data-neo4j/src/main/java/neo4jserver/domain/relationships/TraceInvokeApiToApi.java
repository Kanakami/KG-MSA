package neo4jserver.domain.relationships;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import neo4jserver.domain.entities.ServiceAPI;
import org.neo4j.ogm.annotation.EndNode;
import org.neo4j.ogm.annotation.RelationshipEntity;
import org.neo4j.ogm.annotation.StartNode;

import java.util.Objects;

// 由于Skywalking中，每次调用span有exit和entry类型
// 调用变为3阶段Pod A-> Exit Api -> Entry Api ->Pod B
// 此处TraceInvokeApiToApi 负责从Exit Api 到Entry Api这段
@Getter
@Setter
@NoArgsConstructor
@RelationshipEntity(type="TraceInvokeApiToApi")
public class TraceInvokeApiToApi extends BasicRelationship{
    @StartNode
    private ServiceAPI serviceAPIFrom;

    @EndNode
    private  ServiceAPI serviceAPITo;

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(!(o instanceof TraceInvokeApiToApi))return false;
        if(!super.equals(o))return false;
        TraceInvokeApiToApi that = (TraceInvokeApiToApi) o;
        return Objects.equals(serviceAPIFrom, that.serviceAPIFrom) &&
                Objects.equals(serviceAPITo, that.serviceAPITo);
    }

    @Override
    public int hashCode(){
        return Objects.hash(super.hashCode(), serviceAPIFrom, serviceAPITo);
    }

}

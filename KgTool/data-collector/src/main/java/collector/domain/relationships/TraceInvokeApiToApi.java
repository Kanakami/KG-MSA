package collector.domain.relationships;

import collector.domain.entities.ServiceAPI;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
public class TraceInvokeApiToApi extends BasicRelationship{
    private ServiceAPI serviceAPIFrom;

    private ServiceAPI serviceAPITo;

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

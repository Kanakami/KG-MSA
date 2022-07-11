package collector.domain.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;

@Getter
@Setter
public class MetricSearch {
    private String name;
    private HashMap<String, String> searchKeyToValue;

    public MetricSearch(){
        this.name = null;
        this.searchKeyToValue = new HashMap<>();
    }
}

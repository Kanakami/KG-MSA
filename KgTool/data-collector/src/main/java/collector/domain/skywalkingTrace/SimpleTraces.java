package collector.domain.skywalkingTrace;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;

@NoArgsConstructor
@Getter
@Setter
public class SimpleTraces {
    private String key;
    private int duration;
    private ArrayList<String> endpointName;
    private Boolean isError;
    private String start;
    private ArrayList<String> traceIds;

}

package collector.domain.skywalkingTrace;

import collector.domain.trace.ZipkinSpan;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;

@NoArgsConstructor
@Getter
@Setter
public class SimpleTrace {
    private String key;
    private int duration;
    private ArrayList<String> endpointNames;
    private Boolean isError;
    private String start;
    private ArrayList<String> traceIds;
    private Trace trace;

}

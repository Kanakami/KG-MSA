package collector.domain.skywalkingTrace;

import collector.domain.trace.ZipkinSpan;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;

@NoArgsConstructor
@Getter
@Setter
public class SkywalkingSpan {
    private String component;
    private Long endTime;
    private String endpointName;
    private Boolean isError;
    private String layer;
    private ArrayList<String> logs;
    private Integer parentSpanId;
    private String peer;
    private ArrayList<Ref> refs;
    private String segmentId;
    private String serviceCode;
    private String serviceInstanceName;
    private Integer spanId;
    private Long startTime;
    private ArrayList<Tag> tags;
    private String traceId;
    private String type;
}

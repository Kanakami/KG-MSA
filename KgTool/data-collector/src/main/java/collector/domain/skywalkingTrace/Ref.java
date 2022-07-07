package collector.domain.skywalkingTrace;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Setter
@Getter
public class Ref {
    private String parentSegmentId;
    private Integer parentSpanId;
    private String traceId;
    private String type;
}

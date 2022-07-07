package collector.domain.skywalkingTrace;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;

@NoArgsConstructor
@Setter
@Getter
public class Trace {
    private ArrayList<SkywalkingSpan> spans;
}

package collector.domain.skywalkingTrace;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;

@NoArgsConstructor
@Setter
@Getter
public class Data {
    private int total;
    private ArrayList<SimpleTrace> traces;
}

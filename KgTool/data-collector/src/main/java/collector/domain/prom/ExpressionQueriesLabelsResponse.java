package collector.domain.prom;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;

@NoArgsConstructor
@Getter
@Setter
public class ExpressionQueriesLabelsResponse {
    private String status;
    private ArrayList<String> data;
}

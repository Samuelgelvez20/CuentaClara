package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.BackedRecommendation;
import co.cuentaclara.domain.model.ParsedLineItem;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import java.util.List;
import java.util.Optional;

public interface JsonSerializationPort {

    String serializeRecommendations(List<BackedRecommendation> recommendations);

    List<BackedRecommendation> deserializeRecommendations(String json);

    Optional<TransactionQueryFilters> parseChatIntentFilters(String rawJson);

    List<ParsedLineItem> deserializeItemsJson(String itemsJson);
}

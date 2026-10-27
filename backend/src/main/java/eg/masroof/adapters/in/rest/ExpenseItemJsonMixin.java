package eg.masroof.adapters.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import eg.masroof.adapters.in.rest.generated.model.ExpenseItem;
import org.springframework.boot.jackson.JacksonMixin;

/**
 * The contract's optional item fields ({@code qty}, {@code total}) are absent, never {@code null}
 * (the generated model would write {@code "total": null}, which the schema does not allow).
 */
@JacksonMixin(ExpenseItem.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
abstract class ExpenseItemJsonMixin {}

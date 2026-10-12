package com.showcase.fx.api;

import com.showcase.fx.service.FxQuote;
import com.showcase.fx.service.FxRateService;
import io.micrometer.observation.annotation.Observed;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Observed
@RestController
public class FxRateController {

    private final FxRateService fxRateService;

    public FxRateController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @GetMapping("/fx/rates")
    public FxQuote rate(@RequestParam String base, @RequestParam String quote) {
        return fxRateService.quote(base, quote);
    }
}

package demo.billing;

@RestController
public class InvoiceController {
    @PostMapping("/api/invoices")
    public Object create(@RequestBody Invoice invoice) { return null; }

    @PutMapping("/api/billing/audit")
    public void audit(@RequestBody Object body) { }
}

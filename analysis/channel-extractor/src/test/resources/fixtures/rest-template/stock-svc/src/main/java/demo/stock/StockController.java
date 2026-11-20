package demo.stock;

@RestController
@RequestMapping("/api/stock")
public class StockController {
    @GetMapping("/{itemId}")
    public Item get(@PathVariable long itemId) { return null; }

    @PostMapping("/reserve")
    public Object reserve(@RequestBody Reservation reservation) { return null; }

    @PutMapping("/audit")
    public void audit(@RequestBody Object body) { }

    @PatchMapping("/{itemId}/cancel")
    public void cancel(@PathVariable long itemId) { }

    @PatchMapping("/restock")
    public void restock() { }
}

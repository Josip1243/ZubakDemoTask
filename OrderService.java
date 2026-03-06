@Service
@Transactional
public class OrderService {
    
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRemoteClient userClient;

    private List<String> processedOrderIds = new ArrayList<>();

    public List<OrderResponse> processOrders() {
        List<Order> orders = orderRepository.findAll();

        return orders.stream()
            .map(order => {
                UserDto user = userClient.getUser(order.getUserId());
                processedOrderIds.add(order.getId());
                return new OrderResponse(order.getId(), user.getName(), order.getAmount());
            })
            .collect(Collectors.toList());
    }
}
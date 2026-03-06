@Service
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final UserRemoteClient userClient;

    public OrderService(OrderRepository orderRepository, UserRemoteClient userClient) {
        this.orderRepository = orderRepository;
        this.userClient = userClient;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> processOrders(int page, int size) {
        
        List<String> processedOrderIds = new ArrayList<>();
        Page<Order> orderPage = orderRepository.findAll(PageRequest.of(page, size));
        List<Order> orders = orderPage.getContent();

        // #1 
        // In case we are allowed to change UserRemoteClient
        Set<Long> userIds = orders.stream()
            .map(Order::getUserId)
            .collect(Collectors.toSet());

        List<UserDto> users = fetchUsers(userIds);
        
        Map<Long, UserDto> userMap =
        users.stream()
             .collect(Collectors.toMap(UserDto::getId, Function.identity()));

        return orders.stream()
            .map(order -> {
                processedOrderIds.add(order.getId());
                UserDto user = userMap.get(order.getUserId());
                return new OrderResponse(order.getId(), user.getName(), order.getAmount());
            }).collect(Collectors.toList());
        // #1

        // #2 In case when we are not allowed to make changes in the UserRemoteClient
        // Map<Long, UserDto> cache = new HashMap<>();

        // return orders.stream()
        //     .map(order -> {
        //         UserDto user = cache.computeIfAbsent(
        //             order.getUserId(),
        //             id -> userClient.getUser(id)
        //         );

        //         return new OrderResponse(order.getId(), user.getName(), order.getAmount());
        //     })
        //     .toList();
        // #2
    }

    @Retry(name = "userClient")
    @CircuitBreaker(name = "userClient", fallbackMethod = "fallbackUsers")
    public List<UserDto> fetchUsers(Set<Long> userIds) {
        return userClient.getUsers(userIds);
    }

    public List<UserDto> fallbackUsers(Set<Long> userIds, Throwable ex) {
        return Collections.emptyList();
    }
}
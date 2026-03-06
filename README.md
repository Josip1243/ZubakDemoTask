# ZubakDemoTask

Ispod u nastavku je opisan zadatak vezan uz Java dio.
DockerfileNew predstavlja prepravljeni dio koda za Docker dio, također tako i OrderServiceRefactored je prepravljeni dio koda za Javu.
Unutar Answers.docx su detaljnije objašnjeni odgovori za Docker, CI/CD, metrics.

# 1.Performance Problem

The original implementation retrieves all orders and then calls the `user-service` for every order individually:

```java
UserDto user = userClient.getUser(order.getUserId());
```

This introduces the **N+1 problem** which means that for every order we call user-service.
Which is also O(n) time complexity for network calls, but each "n" can have it's own delay due to the network related issues.

Example:

```
Orders: 500
Remote calls to user-service: 500
```

Each HTTP call adds network latency and increases load on the `user-service`.
Under higher traffic this can lead to:

- increased response time
- network overhead
- higher load on dependent services

## Solution for Performace problem

Main goal of the solution would be to reduce the number of remote calls to the `user-service`.

Two solutions were implemented, depending on the ability to modify user service.

---

## Solution 1 – New Endpoint (Preferred)

If we are allowed to make changes to the `user-service`, then we should expose an API endpoint that accepts multiple user IDs.

### Example

```
POST /users
Body: [1,2,3,4]
```

### The idea for the solution

1. Collect all unique `userId` values from orders.
2. Fetch all users in a single request.
3. Store them in a `Map` for faster lookup.

```java
Set<Long> userIds = orders.stream()
    .map(Order::getUserId)
    .collect(Collectors.toSet());

List<UserDto> users = userClient.getUsers(userIds);

Map<Long, UserDto> userMap =
    users.stream()
        .collect(Collectors.toMap(UserDto::getId, Function.identity()));
```

Orders can then be mapped to responses more quickly:

```java
UserDto user = userMap.get(order.getUserId());
return new OrderResponse(order.getId(), user.getName(), order.getAmount());
```

### Result

Remote network calls are reduced from:

```
N calls → 1 call
```

This significantly improves performance and reduces network overhead.
Even though, the time complexity still remains O(n), but this time there is only one network call and the rest of the logic is done in the memory, which is a lot faster.

---

## Solution 2 – Local Caching (When API Cannot Be Changed)

If modifying the `user-service` API is not possible, then caching can be introduced.

```java
Map<Long, UserDto> cache = new HashMap<>();

UserDto user = cache.computeIfAbsent(
    order.getUserId(),
    id -> userClient.getUser(id)
);
```

This ensures that each user is fetched **only once per request**.

Example:

```
Orders: 100
Unique users: 10
Remote calls: 10
```

While this does not fully eliminate the N+1 pattern, it significantly reduces the number of remote calls.

---

## Benefits of this approach:

- Reduces network calls between microservices
- Improves overall response time
- Reduces load on dependent services

---

# 2. findAll() possible problem

The original implementation retrieves all orders from database to the `memory`.

```java
List<Order> orders = orderRepository.findAll();
```

At first this may not seem like big issue, but let's think of it in a way that 100 users call the endpoint at the same time and we have roughly 1.000.000 records in database. This would result with something like this:

```
Users: 100
Orders: 1.000.000

In total: 100 * 1.000.000 = 100.000.000
```

So that means at the same time we could have up to **100.000.000 records** in the `memory`.

## Risks of this approach:

- Huge RAM consumption
- OutOfMemoryError

## Solution

Introducing the pagination to the service. This ensures that only a limited number of records are processed at a time, which results in `reduced memory usage` and `improved system stability` under high loads.

```java
Page<Order> orderPage = orderRepository.findAll(PageRequest.of(page, size));
List<Order> orders = orderPage.getContent();
```

# 3. processedOrderIds problem

For this case we have the problem with the usage of this list:

```java
private List<String> processedOrderIds = new ArrayList<>();
```

\*Even though, this was excluded from usage in previous examples, let me explain why.

---

## Problem

Usage of this list inside of `@Service` which is by default _Singleton_ causes concurrent issues.
_Singleton_ means we have only 1 instance of this service, but that doesn't mean we cannot have multiple parallel threads using it.

If we have for example 50 simultaneous requests, which all call `processOrders()`, that means they are sharing or using the same exact list `processedOrderIds`.
And all of them are doing this:

```java
processedOrderIds.add(order.getId());
```

Because ArrayList is not thread safe, this causes issues like:

- race condition
- loss of data
- corrupted list
- or even exceptions

## Solution

If the list needs to be used and can be local, better practice would be to keep it in the method scope. This way we prevent all the issues listed above.
In case where it needs to be shared, we could do something like:

```java
CopyOnWriteArrayList
```

ili

```java
Collections.synchronizedList(...)
```

This would allow the list to be shared without the concurrent issues.
Since Spring `@Service` is singleton bean, shared mutable collections must be thread-safe. If processed order IDs must be shared across requests, a concurrent collection such as CopyOnWriteArrayList should be used to avoid race conditions.

But **avoiding shared mutable state** is still the best design when possible.

# 4. @Autowired - Field Injection Problem

With `@Autowired` we are doing the field injection with Spring.

## Problems

Here we have an issue with unit tests. Because Spring automatically injects dependency, while when doing unit tests we must do complex things to achieve the required behavior and dependeies.

Also, it is not visible to the "outside" what this specific `@Service` needs in order to work properly. And, one possible but fairly rare issue that might occur is that the object is initialized without its dependencies, which might cause small time gap in which the service can be called but its dependecies are still null.

## Solution

Solution to this approach would be to completelly remove all `@Autowired` fields and use **Constructor Injection**.

Example:

```java
private final OrderRepository orderRepository;
private final UserRemoteClient userClient;

public OrderService(OrderRepository orderRepository, UserRemoteClient userClient) {
    this.orderRepository = orderRepository;
    this.userClient = userClient;
}
```

This way we can have easier unit testing, we are making the dependencies explicit and we are sure that the service will always be fully initialized.

# 5. @Transactional

`@Transactional` was originally at the class level, which causes all methods to run within a transaction. Since `processOrders` method performs only read operations, the transaction scope can be reduced and marked as `readOnly = true` to improve performance and reduce unnecessary database locking.

```java
@Transactional(readOnly = true)
public List<OrderResponse> processOrders(int page, int size)
```

# 6. No error handling

In this service we are calling `user-service`.
The possible error that could happen here is that this service might be down, we could have a timeout or network issues and that would cause exception and the whole request to fail.

In order to prevent that we could implement some mechanism to cope with that problem.
The most simple, but not the best solution would be to use `try/catch`.
Better solution would be to use resilience patterns:

- timeout
- retry
- circuit breaker

```java
@Retry(name = "userClient")
@CircuitBreaker(name = "userClient", fallbackMethod = "fallbackUsers")
public List<UserDto> fetchUsers(Set<Long> userIds) {
    return userClient.getUsers(userIds);
}

public List<UserDto> fallbackUsers(Set<Long> userIds, Throwable ex) {
    return Collections.emptyList();
}
```

This way we implemented:

- retry -> to try sending a message few times
- circuit breaker -> to limit retries in order to protect system from overload
- fallback -> to return safe result

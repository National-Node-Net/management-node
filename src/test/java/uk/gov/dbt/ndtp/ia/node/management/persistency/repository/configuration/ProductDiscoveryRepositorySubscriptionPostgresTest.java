package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Consumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductConsumer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;

class ProductDiscoveryRepositorySubscriptionPostgresTest extends AbstractPostgresRepositoryTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private OrganisationRepository organisationRepository;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private ConsumerRepository consumerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductConsumerRepository productConsumerRepository;

    private ProductDiscoveryRepository repository;
    private Organisation subscriber;
    private Organisation other;
    private Product onTwoConsumers;
    private Product expired;
    private Product othersOnly;
    private Product nobodys;

    @BeforeEach
    void setUp() {
        repository = new ProductDiscoveryRepository(jdbc, new DiscoverySchema(""));

        Organisation owner = organisation();
        Producer producer = producer(owner);
        onTwoConsumers = product(producer);
        expired = product(producer);
        othersOnly = product(producer);
        nobodys = product(producer);

        subscriber = organisation();
        Consumer first = consumer(subscriber);
        Consumer second = consumer(subscriber);
        grant(onTwoConsumers, first, Instant.now(), 30);
        grant(onTwoConsumers, second, Instant.now(), 30);
        grant(expired, first, Instant.now().minus(400, ChronoUnit.DAYS), 30);

        other = organisation();
        grant(othersOnly, consumer(other), Instant.now(), 30);
    }

    @Test
    void reportsEveryProductTheOrganisationHoldsAGrantOn_onceEach_whateverItsValidity() {
        assertThat(repository.findSubscribedProductIds(subscriber.getOrganisationKey(), allProducts()))
                .containsExactlyInAnyOrder(onTwoConsumers.getId(), expired.getId());
    }

    @Test
    void doesNotReportAnotherOrganisationsGrants() {
        assertThat(repository.findSubscribedProductIds(other.getOrganisationKey(), allProducts()))
                .containsExactly(othersOnly.getId());
    }

    @Test
    void isLimitedToTheProductsAskedAbout() {
        assertThat(repository.findSubscribedProductIds(
                        subscriber.getOrganisationKey(), List.of(othersOnly.getId(), nobodys.getId())))
                .isEmpty();
    }

    @Test
    void unknownOrganisation_holdsNothing() {
        assertThat(repository.findSubscribedProductIds("NO_SUCH_ORG", allProducts()))
                .isEmpty();
    }

    private List<Long> allProducts() {
        return List.of(onTwoConsumers.getId(), expired.getId(), othersOnly.getId(), nobodys.getId());
    }

    private Organisation organisation() {
        Organisation organisation = new Organisation();
        organisation.setName("Subscription Test Org");
        organisation.setOrganisationKey("SUB_" + unique());
        return organisationRepository.saveAndFlush(organisation);
    }

    private Producer producer(Organisation organisation) {
        Producer producer = new Producer();
        producer.setName("Producer " + unique());
        producer.setDescription("Subscription test producer");
        producer.setOrg(organisation);
        producer.setActive(true);
        producer.setHost("localhost");
        producer.setPort(BigDecimal.valueOf(8080));
        producer.setTls(true);
        producer.setIdpClientId("producer-" + unique());
        return producerRepository.saveAndFlush(producer);
    }

    private Product product(Producer producer) {
        Product product = new Product();
        product.setName("Product " + unique());
        product.setTopic("topic." + unique());
        product.setProducer(producer);
        return productRepository.saveAndFlush(product);
    }

    private Consumer consumer(Organisation organisation) {
        Consumer consumer = new Consumer();
        consumer.setName("Consumer " + unique());
        consumer.setOrg(organisation);
        consumer.setIdpClientId("consumer-" + unique());
        consumer.setScheduleType("cron");
        return consumerRepository.saveAndFlush(consumer);
    }

    private void grant(Product product, Consumer consumer, Instant grantedAt, int validityDays) {
        ProductConsumer grant = new ProductConsumer();
        grant.setProduct(product);
        grant.setConsumer(consumer);
        grant.setGrantedTs(Timestamp.from(grantedAt));
        grant.setValidity(BigDecimal.valueOf(validityDays));
        grant.setScheduleType("cron");
        productConsumerRepository.saveAndFlush(grant);
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

package com.gamehub.config.properties;

import com.gamehub.domain.matchmaking.MatchmakingPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Externalised matchmaking configuration, bound from the gamehub.matchmaking
 * prefix.
 *
 * <p>Validated at startup rather than at first use. A nonsensical weight in a
 * config map should stop the instance from starting, not surface hours later as
 * players quietly getting unfair matches. That is the whole point of putting
 * jakarta validation annotations on a properties class.
 *
 * <p>This type exists so that the pure domain
 * {@link com.gamehub.domain.matchmaking.MatchmakingPolicy} never has to know
 * about Spring. {@link #toPolicy()} is the one-way bridge.
 */
@Validated
@ConfigurationProperties(prefix = "gamehub.matchmaking")
public class MatchmakingProperties {

    @PositiveOrZero
    private double skillWeight = 0.50d;

    @PositiveOrZero
    private double latencyWeight = 0.30d;

    @PositiveOrZero
    private double regionWeight = 0.20d;

    @PositiveOrZero
    private double patienceWeight = 0.40d;

    @Positive
    private double skillNormaliserElo = 400.0d;

    @Positive
    private double latencyBudgetMs = 150.0d;

    @Min(1)
    private int baseSkillWindowElo = 100;

    @PositiveOrZero
    private double windowExpansionPerSec = 25.0d;

    @Min(1)
    private int maxSkillWindowElo = 1200;

    @PositiveOrZero
    private double baseAcceptanceCost = 0.25d;

    @PositiveOrZero
    private double maxAcceptanceCost = 0.90d;

    @Positive
    private double patienceSeconds = 45.0d;

    /**
     * How often each instance attempts a matchmaker tick.
     *
     * <p>Not the same as how often a tick actually runs: every instance wakes
     * on this interval, but only the one that wins the Redis lease for a given
     * bucket does any work. See docs/matchmaking.md.
     */
    @NotNull
    private Duration tickInterval = Duration.ofSeconds(1);

    /**
     * How long a ticket stays queued before the sweeper expires it.
     *
     * <p>A ceiling, not a target. The acceptance threshold should have matched
     * the player long before this fires; a ticket actually reaching expiry is a
     * signal that the population for that bucket is too thin, and it is
     * reported as such rather than being silently retried forever.
     */
    @NotNull
    private Duration ticketTtl = Duration.ofMinutes(5);

    /** Upper bound on candidates examined per ticket in one tick. */
    @Min(1)
    private int candidateLimit = 50;

    /** Maximum tickets pulled from one bucket in one tick. */
    @Min(2)
    private int bucketBatchSize = 500;

    /**
     * Translates these properties into the immutable domain policy.
     *
     * <p>Constructing the record here means its constructor invariants (for
     * example, maxSkillWindowElo not below baseSkillWindowElo) are checked as
     * part of application startup, catching cross-field mistakes that
     * per-field annotations cannot express.
     */
    public MatchmakingPolicy toPolicy() {
        return new MatchmakingPolicy(
                skillWeight,
                latencyWeight,
                regionWeight,
                patienceWeight,
                skillNormaliserElo,
                latencyBudgetMs,
                baseSkillWindowElo,
                windowExpansionPerSec,
                maxSkillWindowElo,
                baseAcceptanceCost,
                maxAcceptanceCost,
                patienceSeconds);
    }

    public double getSkillWeight() { return skillWeight; }
    public void setSkillWeight(double v) { this.skillWeight = v; }

    public double getLatencyWeight() { return latencyWeight; }
    public void setLatencyWeight(double v) { this.latencyWeight = v; }

    public double getRegionWeight() { return regionWeight; }
    public void setRegionWeight(double v) { this.regionWeight = v; }

    public double getPatienceWeight() { return patienceWeight; }
    public void setPatienceWeight(double v) { this.patienceWeight = v; }

    public double getSkillNormaliserElo() { return skillNormaliserElo; }
    public void setSkillNormaliserElo(double v) { this.skillNormaliserElo = v; }

    public double getLatencyBudgetMs() { return latencyBudgetMs; }
    public void setLatencyBudgetMs(double v) { this.latencyBudgetMs = v; }

    public int getBaseSkillWindowElo() { return baseSkillWindowElo; }
    public void setBaseSkillWindowElo(int v) { this.baseSkillWindowElo = v; }

    public double getWindowExpansionPerSec() { return windowExpansionPerSec; }
    public void setWindowExpansionPerSec(double v) { this.windowExpansionPerSec = v; }

    public int getMaxSkillWindowElo() { return maxSkillWindowElo; }
    public void setMaxSkillWindowElo(int v) { this.maxSkillWindowElo = v; }

    public double getBaseAcceptanceCost() { return baseAcceptanceCost; }
    public void setBaseAcceptanceCost(double v) { this.baseAcceptanceCost = v; }

    public double getMaxAcceptanceCost() { return maxAcceptanceCost; }
    public void setMaxAcceptanceCost(double v) { this.maxAcceptanceCost = v; }

    public double getPatienceSeconds() { return patienceSeconds; }
    public void setPatienceSeconds(double v) { this.patienceSeconds = v; }

    public Duration getTickInterval() { return tickInterval; }
    public void setTickInterval(Duration v) { this.tickInterval = v; }

    public Duration getTicketTtl() { return ticketTtl; }
    public void setTicketTtl(Duration v) { this.ticketTtl = v; }

    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int v) { this.candidateLimit = v; }

    public int getBucketBatchSize() { return bucketBatchSize; }
    public void setBucketBatchSize(int v) { this.bucketBatchSize = v; }
}

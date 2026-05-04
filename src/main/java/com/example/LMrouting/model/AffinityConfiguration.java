package com.example.LMrouting.model;

import lombok.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * AffinityConfiguration represents a complete affinity configuration that can be saved and reused.
 * 
 * This entity contains all SR-region assignments for a specific hub on a given date.
 * Configurations can be saved with a name for future reuse, allowing supervisors to quickly
 * apply previously tested allocation strategies without reconfiguring region assignments.
 * 
 * The isSaved flag indicates whether this configuration has been persisted for future use.
 * Unsaved configurations are temporary and used only for preview/comparison purposes.
 * 
 * Pure POJO, no JPA annotations. All persistence is handled by InMemoryStore.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffinityConfiguration {
    
    /**
     * Unique identifier for this configuration
     */
    private Long id;
    
    /**
     * User-defined name for this configuration (e.g., "Peak Season Config", "Weekend Setup")
     * Required when isSaved is true, optional for temporary configurations
     */
    private String configName;
    
    /**
     * Date when this configuration was created
     */
    private LocalDate createdDate;
    
    /**
     * Name of the hub this configuration applies to
     * Configurations are hub-specific since region boundaries and SR assignments vary by hub
     */
    private String hubName;
    
    /**
     * List of all SR-region assignments in this configuration
     * Contains the complete mapping of which SRs are assigned to which affinity regions
     */
    @Builder.Default
    private List<SrRegionAssignment> srRegionAssignments = new ArrayList<>();
    
    /**
     * Flag indicating whether this configuration has been saved for future reuse
     * - true: Configuration is persisted and can be loaded by name
     * - false: Configuration is temporary (used for preview/comparison only)
     */
    private boolean isSaved;
}

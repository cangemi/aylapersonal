package ayla.hermes;

import jakarta.persistence.*;

@Entity
@Table(name = "hermes_configuration")
public class HermesConfiguration {
    @Id
    private Long id = 1L;
    @Column(length = 255)
    private String model;
    @Column(length = 4000, nullable = false)
    private String instructions = "";
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
}

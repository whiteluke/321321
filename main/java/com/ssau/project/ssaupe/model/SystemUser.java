package com.ssau.project.ssaupe.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;



@Setter
@Getter
@Entity
@Table(name = "systemUser")
public class SystemUser {

    private Long id;
    private String username;
    private String password;
    private String passwordConfirm;
    private Set<Role> roles;

    private String lastName;                            // Фамилия
    private String firstName;                           // Имя
    private String middleName;                          // Отчество
    private String volunteerBookId;                     // Номер волонтёрской книжки
    private LocalDate birthDate;                        // Дата рождения
    private String email;                               // Электронная почта
    private String phoneNumber;                         // Номер телефона
    private String additionalLanguage;                  // Дополнительный язык
    private Set<ParticipationExperience> experiences;   // Опыт участия (Checkboxes)
    @Lob
    @Column(columnDefinition = "BYTEA")
    private byte[] profile_picture;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long getId() {
        return id;
    }

    @Transient
    public String getPasswordConfirm() {
        return passwordConfirm;
    }

    @ManyToMany
    @JoinTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    public Set<Role> getRoles() {
        return roles;
    }

    @ElementCollection(targetClass = ParticipationExperience.class)
    @CollectionTable(name = "user_experience", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "experience_type")
    public Set<ParticipationExperience> getExperiences() {
        return experiences;
    }

    public void setProfile_picture(MultipartFile profile_picture) throws IOException {
        if (profile_picture != null && !profile_picture.isEmpty()) {
            this.profile_picture = profile_picture.getBytes();
        }
    }
}

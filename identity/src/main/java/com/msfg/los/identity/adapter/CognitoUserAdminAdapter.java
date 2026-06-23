package com.msfg.los.identity.adapter;

import com.msfg.los.platform.security.Role;
import com.msfg.los.platform.security.UserAdminPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminResetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DeliveryMediumType;

import java.util.Map;

/**
 * Real Cognito write-side {@link UserAdminPort} — active only when {@code los.identity.user-admin=cognito}
 * (the {@code StubUserAdminAdapter} is the default, so this is dormant locally and in tests). AWS
 * credentials come from the default provider chain (env / instance role).
 *
 * <p><b>Cutover validation (do before flipping to {@code cognito}):</b>
 * <ul>
 *   <li>Username scheme — this adapter uses the email as the Cognito username. If the pool uses the
 *       {@code sub} as username, {@link #resetPassword} must resolve username from the stored sub
 *       (e.g. {@code listUsers} filtered by {@code sub}). Confirm against pool {@code us-west-1_S6iE2uego}.</li>
 *   <li>Group names — {@link #COGNITO_GROUP} is the inverse of {@code CognitoRolesConverter.GROUP_ALIASES};
 *       keep the two in sync, and confirm the real pool's group strings match.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "los.identity.user-admin", havingValue = "cognito")
public class CognitoUserAdminAdapter implements UserAdminPort {

    /** Inverse of {@code CognitoRolesConverter.GROUP_ALIASES}; roles absent here use their enum name. */
    private static final Map<String, String> COGNITO_GROUP = Map.of(
            Role.ADMIN.name(), "Admin",
            Role.MANAGER.name(), "Manager",
            Role.LO.name(), "LO",
            Role.PROCESSOR.name(), "Processor",
            Role.BORROWER.name(), "Borrower",
            Role.REAL_ESTATE_AGENT.name(), "RealEstateAgent");

    private final CognitoIdentityProviderClient client;
    private final String userPoolId;

    public CognitoUserAdminAdapter(
            @Value("${los.identity.cognito.user-pool-id}") String userPoolId,
            @Value("${los.identity.cognito.region:us-west-1}") String region) {
        this.userPoolId = userPoolId;
        this.client = CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public String createUser(NewUser user) {
        AdminCreateUserResponse resp = client.adminCreateUser(AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(user.email())
                .userAttributes(
                        AttributeType.builder().name("email").value(user.email()).build(),
                        AttributeType.builder().name("email_verified").value("true").build(),
                        AttributeType.builder().name("name").value(user.name()).build())
                .desiredDeliveryMediums(DeliveryMediumType.EMAIL)   // Cognito sends the invite + temp password
                .build());

        String group = groupFor(user.role());
        if (group != null) {
            client.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(user.email())
                    .groupName(group)
                    .build());
        }

        // The immutable Cognito sub is the suite user_account id.
        return resp.user().attributes().stream()
                .filter(a -> "sub".equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElseGet(() -> resp.user().username());
    }

    @Override
    public void resetPassword(String subjectId) {
        // subjectId is the stored Cognito sub. See class javadoc: if username != sub in this pool,
        // resolve the username from the sub before calling this at cutover.
        client.adminResetUserPassword(AdminResetUserPasswordRequest.builder()
                .userPoolId(userPoolId)
                .username(subjectId)
                .build());
    }

    private static String groupFor(String role) {
        if (role == null) return null;
        return COGNITO_GROUP.getOrDefault(role, role);
    }
}

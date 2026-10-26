package ai.dvine.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DeviceController {
    public record RegisterRequest(@NotBlank @Size(max = 128) String deviceId) {
    }

    public record SignInRequest(@NotBlank @Size(max = 128) String deviceId,
                                @NotBlank @Size(max = 200) String deviceSecret) {
    }

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/api/v1/devices/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        DeviceService.Registered r = devices.register(body.deviceId(), request.getRemoteAddr());
        return Map.of("userId", r.userId(), "deviceSecret", r.deviceSecret());
    }

    @PostMapping("/api/v1/sessions/device")
    public Map<String, String> signIn(@Valid @RequestBody SignInRequest body) {
        SessionService.Issued issued = devices.signIn(body.deviceId(), body.deviceSecret());
        return Map.of("token", issued.token(), "userId", issued.userId(), "expiresAt", issued.expiresAt().toString());
    }
}

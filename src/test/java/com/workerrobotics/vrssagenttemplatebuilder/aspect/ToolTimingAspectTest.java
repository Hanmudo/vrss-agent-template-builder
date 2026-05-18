package com.workerrobotics.vrssagenttemplatebuilder.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolTimingAspectTest {

    @InjectMocks
    private ToolTimingAspect aspect;

    @Mock
    private ProceedingJoinPoint pjp;

    @Test
    void time_returnsValueFromProceed() throws Throwable {
        var signature = mock(Signature.class);
        when(signature.getName()).thenReturn("getRandomDogFacts");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{1});
        when(pjp.proceed()).thenReturn("A dog fact.");

        Object result = aspect.time(pjp);

        assertEquals("A dog fact.", result);
    }

    @Test
    void time_rethrowsExceptionFromProceed() throws Throwable {
        var signature = mock(Signature.class);
        when(signature.getName()).thenReturn("getRandomDogFacts");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{1});
        when(pjp.proceed()).thenThrow(new RuntimeException("upstream failure"));

        assertThrows(RuntimeException.class, () -> aspect.time(pjp));
    }
}

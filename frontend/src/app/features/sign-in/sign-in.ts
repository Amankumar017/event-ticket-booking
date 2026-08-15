import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../../core/auth';

@Component({
  selector: 'app-sign-in',
  imports: [FormsModule],
  templateUrl: './sign-in.html',
  styleUrl: './sign-in.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignIn {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  protected readonly email = signal('customer@example.com');
  protected readonly password = signal('');
  protected readonly registering = signal(false);
  protected readonly displayName = signal('');
  protected readonly working = signal(false);
  protected readonly problem = signal<string | null>(null);

  /** Where to go once signed in, as set by whatever sent us here. */
  private readonly returnTo =
    inject(ActivatedRoute).snapshot.queryParamMap.get('returnTo') ?? '/';

  protected toggleMode(): void {
    this.registering.update((value) => !value);
    this.problem.set(null);
  }

  protected submit(): void {
    this.working.set(true);
    this.problem.set(null);

    const request = this.registering()
      ? this.auth.register(this.email(), this.password(), this.displayName())
      : this.auth.login(this.email(), this.password());

    request.subscribe({
      next: () => {
        this.working.set(false);
        this.router.navigateByUrl(this.returnTo);
      },
      error: (failure: unknown) => {
        const problem = (failure as { error?: { detail?: string } })?.error;
        this.problem.set(problem?.detail ?? 'Could not sign you in. Please try again.');
        this.working.set(false);
      },
    });
  }
}

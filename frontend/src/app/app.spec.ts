import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();
  });

  it('creates the shell', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows the brand in the masthead', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const brand = (fixture.nativeElement as HTMLElement).querySelector('.masthead__brand');

    expect(brand?.textContent).toContain('Seatly');
  });
});

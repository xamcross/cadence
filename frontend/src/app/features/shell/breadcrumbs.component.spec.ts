import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Component } from '@angular/core';
import { BreadcrumbsComponent } from './breadcrumbs.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

@Component({ standalone: true, template: 'x' }) class Dummy {}

describe('BreadcrumbsComponent', () => {
  let fixture: ComponentFixture<BreadcrumbsComponent>;
  let el: HTMLElement;
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [BreadcrumbsComponent],
      providers: [provideRouter([
        { path: 'pipeline', component: Dummy },
        { path: 'app', component: Dummy },
        { path: 'pipeline/candidate/:id/timeline', component: Dummy, data: { breadcrumb: 'Candidate timeline' } }
      ])]
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(BreadcrumbsComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
  });
  afterEach(() => detachFromBody(el));

  it('shows Home > <nav label> for a nav route', async () => {
    await router.navigateByUrl('/pipeline');
    fixture.detectChanges();
    const items = el.querySelectorAll('.breadcrumbs__item');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('Home');
    expect(items[1].textContent).toContain('Pipeline');
    expect(items[1].querySelector('[aria-current="page"]')).not.toBeNull();
  });

  it('prefers route data.breadcrumb for a drill-down route', async () => {
    await router.navigateByUrl('/pipeline/candidate/c1/timeline');
    fixture.detectChanges();
    const items = el.querySelectorAll('.breadcrumbs__item');
    expect(items[items.length - 1].textContent).toContain('Candidate timeline');
  });

  it('renders only Home on the launchpad', async () => {
    await router.navigateByUrl('/app');
    fixture.detectChanges();
    expect(el.querySelector('nav.breadcrumbs')).toBeNull(); // hidden when only Home
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    await router.navigateByUrl('/pipeline');
    fixture.detectChanges();
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});

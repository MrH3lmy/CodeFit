package com.codefit.controller;

import com.codefit.ui.NavigationService;

public abstract class BaseController {
    public void goDashboard() { NavigationService.showDashboard(); }
    public void goDecks() { NavigationService.showDecks(); }
    public void goAddCard() { NavigationService.showAddCard(); }
    public void goReview() { NavigationService.showReview(); }
    public void goStats() { NavigationService.showStats(); }
}
